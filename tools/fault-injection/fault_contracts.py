"""
fault_contracts.py

Core contract types and simulation primitives for MCA Fault Injection Harness.
Mirroring Kotlin/C++ contracts in core:engine and core:native:
- RequestState and RequestStateMachine
- RequestIdentity and Monotonic Sequence
- StableErrorCode, ErrorCategory, ErrorPhase, Retryability
- ErrorEnvelope and Public API Sanitizer
- CallbackRouter with backpressure and stale event filtering
"""

from __future__ import annotations

import time
import uuid
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Dict, List, Optional, Set


class RequestState(str, Enum):
    Created = "Created"
    Accepted = "Accepted"
    Preparing = "Preparing"
    Running = "Running"
    CancelRequested = "CancelRequested"
    DrainingCallbacks = "DrainingCallbacks"
    Succeeded = "Succeeded"
    Failed = "Failed"
    Cancelled = "Cancelled"
    TimedOut = "TimedOut"
    Aborted = "Aborted"

    @property
    def is_terminal(self) -> bool:
        return self in {
            RequestState.Succeeded,
            RequestState.Failed,
            RequestState.Cancelled,
            RequestState.TimedOut,
            RequestState.Aborted,
        }

    @property
    def is_cancellable(self) -> bool:
        return self in {
            RequestState.Created,
            RequestState.Accepted,
            RequestState.Preparing,
            RequestState.Running,
        }

    def can_transition_to(self, target: "RequestState") -> bool:
        if self.is_terminal:
            return False
        valid_transitions: Dict[RequestState, Set[RequestState]] = {
            RequestState.Created: {
                RequestState.Accepted,
                RequestState.Failed,
                RequestState.Cancelled,
            },
            RequestState.Accepted: {
                RequestState.Preparing,
                RequestState.Running,
                RequestState.CancelRequested,
                RequestState.Failed,
                RequestState.Cancelled,
            },
            RequestState.Preparing: {
                RequestState.Running,
                RequestState.CancelRequested,
                RequestState.Failed,
                RequestState.TimedOut,
                RequestState.Aborted,
            },
            RequestState.Running: {
                RequestState.Succeeded,
                RequestState.CancelRequested,
                RequestState.DrainingCallbacks,
                RequestState.Failed,
                RequestState.TimedOut,
                RequestState.Aborted,
            },
            RequestState.CancelRequested: {
                RequestState.DrainingCallbacks,
                RequestState.Cancelled,
                RequestState.Failed,
                RequestState.Aborted,
            },
            RequestState.DrainingCallbacks: {
                RequestState.Cancelled,
                RequestState.Failed,
                RequestState.Aborted,
            },
        }
        return target in valid_transitions.get(self, set())


class StableErrorCode(str, Enum):
    NONE = "NONE"
    # Validation
    VAL_INVALID_INPUT = "VAL_INVALID_INPUT"
    VAL_MISSING_REQUIRED_FIELD = "VAL_MISSING_REQUIRED_FIELD"
    VAL_CORRUPT_PACKAGE = "VAL_CORRUPT_PACKAGE"
    VAL_INVALID_FORMAT = "VAL_INVALID_FORMAT"
    VAL_ILLEGAL_ARGUMENT = "VAL_ILLEGAL_ARGUMENT"
    # Capability
    CAP_UNSUPPORTED_OPERATION = "CAP_UNSUPPORTED_OPERATION"
    CAP_BACKEND_UNAVAILABLE = "CAP_BACKEND_UNAVAILABLE"
    CAP_PRECISION_UNSUPPORTED = "CAP_PRECISION_UNSUPPORTED"
    CAP_DEVICE_FALLBACK_REQUIRED = "CAP_DEVICE_FALLBACK_REQUIRED"
    # Model
    MDL_NOT_FOUND = "MDL_NOT_FOUND"
    MDL_CHECKSUM_MISMATCH = "MDL_CHECKSUM_MISMATCH"
    MDL_LOAD_FAILED = "MDL_LOAD_FAILED"
    MDL_CORRUPT_BUNDLE = "MDL_CORRUPT_BUNDLE"
    MDL_MISSING_REQUIRED_BINARY = "MDL_MISSING_REQUIRED_BINARY"
    MDL_INCOMPATIBLE_ARCHITECTURE = "MDL_INCOMPATIBLE_ARCHITECTURE"
    # Runtime
    RT_OUT_OF_MEMORY = "RT_OUT_OF_MEMORY"
    RT_INITIALIZATION_FAILED = "RT_INITIALIZATION_FAILED"
    RT_CRASHED = "RT_CRASHED"
    RT_DEVICE_LOST = "RT_DEVICE_LOST"
    RT_UNHEALTHY = "RT_UNHEALTHY"
    RT_CONCURRENT_LIMIT_EXCEEDED = "RT_CONCURRENT_LIMIT_EXCEEDED"
    # Native
    NAT_DSO_LOAD_FAILED = "NAT_DSO_LOAD_FAILED"
    NAT_SYMBOL_NOT_FOUND = "NAT_SYMBOL_NOT_FOUND"
    NAT_EXECUTION_FAULT = "NAT_EXECUTION_FAULT"
    NAT_ABORTED = "NAT_ABORTED"
    NAT_JNI_CALL_FAILED = "NAT_JNI_CALL_FAILED"
    # Request
    REQ_INVALID_STATE = "REQ_INVALID_STATE"
    REQ_DUPLICATE_TERMINAL = "REQ_DUPLICATE_TERMINAL"
    REQ_STALE_CALLBACK = "REQ_STALE_CALLBACK"
    REQ_REJECTED = "REQ_REJECTED"
    REQ_NOT_FOUND = "REQ_NOT_FOUND"
    # Cancel
    CNC_USER_CANCELLED = "CNC_USER_CANCELLED"
    CNC_DRAIN_TIMEOUT = "CNC_DRAIN_TIMEOUT"
    CNC_ALREADY_CANCELLED = "CNC_ALREADY_CANCELLED"
    # Timeout
    TIM_LOAD_TIMEOUT = "TIM_LOAD_TIMEOUT"
    TIM_EXECUTION_TIMEOUT = "TIM_EXECUTION_TIMEOUT"
    TIM_DRAIN_TIMEOUT = "TIM_DRAIN_TIMEOUT"
    TIM_HEARTBEAT_EXPIRED = "TIM_HEARTBEAT_EXPIRED"
    # Storage
    STR_READ_ERROR = "STR_READ_ERROR"
    STR_WRITE_ERROR = "STR_WRITE_ERROR"
    STR_DISK_FULL = "STR_DISK_FULL"
    STR_PERMISSION_DENIED = "STR_PERMISSION_DENIED"
    # Network
    NET_CONNECT_FAILED = "NET_CONNECT_FAILED"
    NET_TIMEOUT = "NET_TIMEOUT"
    NET_PROTOCOL_ERROR = "NET_PROTOCOL_ERROR"
    # Auth
    AUTH_MISSING_KEY = "AUTH_MISSING_KEY"
    AUTH_INVALID_KEY = "AUTH_INVALID_KEY"
    AUTH_FORBIDDEN = "AUTH_FORBIDDEN"
    # Internal
    INT_UNEXPECTED_EXCEPTION = "INT_UNEXPECTED_EXCEPTION"
    INT_CORRUPTED_STATE = "INT_CORRUPTED_STATE"


class ErrorCategory(str, Enum):
    Validation = "Validation"
    Capability = "Capability"
    Model = "Model"
    Runtime = "Runtime"
    Native = "Native"
    Request = "Request"
    Cancel = "Cancel"
    Timeout = "Timeout"
    Storage = "Storage"
    Network = "Network"
    Auth = "Auth"
    Internal = "Internal"


class ErrorPhase(str, Enum):
    Validate = "Validate"
    Load = "Load"
    Prepare = "Prepare"
    Execute = "Execute"
    Callback = "Callback"
    Persist = "Persist"
    Shutdown = "Shutdown"


class Retryability(str, Enum):
    Never = "Never"
    SafeRetry = "SafeRetry"
    RetryAfterRecovery = "RetryAfterRecovery"
    UserAction = "UserAction"


@dataclass(frozen=True)
class RequestIdentity:
    request_id: str = field(default_factory=lambda: str(uuid.uuid4()))
    attempt_id: int = 1
    runtime_epoch: int = 1
    session_generation: int = 1
    worker_incarnation: int = 1
    native_sequence: int = 0


@dataclass
class ErrorEnvelope:
    code: StableErrorCode
    category: ErrorCategory
    phase: ErrorPhase
    retryability: Retryability
    user_message_key: str
    public_message: str
    cause_chain: List[str] = field(default_factory=list)
    identity: Optional[RequestIdentity] = None
    native_code: Optional[int] = None
    http_code: Optional[int] = None
    os_code: Optional[int] = None
    trace_id: str = field(default_factory=lambda: str(uuid.uuid4()))
    timestamp_ms: int = field(default_factory=lambda: int(time.time() * 1000))

    def sanitize_for_public_api(self) -> Dict[str, Any]:
        """Strip internal native/OS codes, memory pointers, and raw paths."""
        sanitized_causes = []
        for cause in self.cause_chain:
            # Strip local storage paths and hex memory addresses
            c = cause.replace("E:\\MAC\\", "").replace("/data/data/com.muyuchat.mca/", "")
            import re
            c = re.sub(r"0x[0-9a-fA-F]{6,16}", "0x<redacted>", c)
            sanitized_causes.append(c)

        return {
            "code": self.code.value,
            "category": self.category.value,
            "phase": self.phase.value,
            "retryability": self.retryability.value,
            "user_message_key": self.user_message_key,
            "public_message": self.public_message,
            "cause_chain": sanitized_causes,
            "http_code": self.http_code,
            "trace_id": self.trace_id,
            "timestamp_ms": self.timestamp_ms,
        }


class RequestStateMachine:
    def __init__(self, initial_identity: RequestIdentity):
        self._identity = initial_identity
        self._state = RequestState.Created
        self._state_history: List[RequestState] = [RequestState.Created]
        self._last_seen_sequence: int = -1
        self._terminal_callback_count: int = 0

    @property
    def current_state(self) -> RequestState:
        return self._state

    @property
    def is_terminal(self) -> bool:
        return self._state.is_terminal

    @property
    def state_history(self) -> List[RequestState]:
        return list(self._state_history)

    def transition_to(self, target: RequestState) -> bool:
        if self._state == target:
            return True
        if not self._state.can_transition_to(target):
            return False
        self._state = target
        self._state_history.append(target)
        if target.is_terminal:
            self._terminal_callback_count += 1
        return True

    def request_cancel(self) -> bool:
        if self._state.is_terminal:
            return False
        if self._state in {RequestState.CancelRequested, RequestState.DrainingCallbacks}:
            return True
        if self._state == RequestState.Created:
            return self.transition_to(RequestState.Cancelled)
        return self.transition_to(RequestState.CancelRequested)

    def begin_draining(self) -> bool:
        if self._state not in {RequestState.CancelRequested, RequestState.Running}:
            return False
        return self.transition_to(RequestState.DrainingCallbacks)


class CallbackRouter:
    def __init__(self, max_buffer_size: int = 10):
        self.max_buffer_size = max_buffer_size
        self.queue: List[Dict[str, Any]] = []
        self.coalesced_progress_count = 0
        self.dropped_stale_count = 0
        self.delivered_terminals = 0
        self.highest_sequence_seen = -1

    def push_event(self, event: Dict[str, Any], current_identity: RequestIdentity) -> str:
        seq = event.get("sequence", 0)
        epoch = event.get("runtime_epoch", 1)
        gen = event.get("session_generation", 1)

        # Stale event check
        if (
            epoch < current_identity.runtime_epoch
            or gen < current_identity.session_generation
            or seq <= self.highest_sequence_seen
        ):
            self.dropped_stale_count += 1
            return "STALE_DROPPED"

        self.highest_sequence_seen = seq

        if event.get("type") == "progress":
            # Coalesce intermediate progress when buffer fills
            if len(self.queue) >= self.max_buffer_size:
                # Discard older progress event
                for idx, queued in enumerate(self.queue):
                    if queued.get("type") == "progress":
                        self.queue.pop(idx)
                        self.coalesced_progress_count += 1
                        break
            self.queue.append(event)
            return "QUEUED"

        if event.get("type") == "terminal":
            self.delivered_terminals += 1
            self.queue.append(event)
            return "TERMINAL_QUEUED"

        self.queue.append(event)
        return "QUEUED"
