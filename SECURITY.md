# Security Policy

MCA handles local models, local files, and user-provided cloud API credentials.

## Supported Versions

The project is pre-release. Security fixes should target the current default
branch unless a release branch is created later.

## Reporting a Vulnerability

If the repository is public, please use GitHub private vulnerability reporting
or open a minimal issue that does not disclose exploit details. If private
reporting is not available, contact the maintainer through the repository owner
profile.

## Sensitive Data Rules

- Do not post API keys, tokens, private model URLs, or account credentials in issues.
- Do not attach generated logs that contain prompts or provider responses unless
  you have reviewed them.
- Do not upload proprietary model weights unless the model license explicitly
  allows redistribution.

## Cloud API Keys

MCA stores configured cloud API keys locally using Android Keystore-backed
encryption. Providers still receive prompts and uploaded request content when a
cloud engine is selected.
