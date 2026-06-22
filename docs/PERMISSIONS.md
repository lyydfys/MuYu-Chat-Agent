# Android Permissions

MCA keeps the Android permission surface intentionally small.

## Declared Permissions

| Permission | Why it is used |
|---|---|
| `INTERNET` | Connect to user-configured cloud API endpoints, ModelScope downloads, and the optional local API/web surfaces. |
| `ACCESS_NETWORK_STATE` | Detect network availability before cloud calls, downloads, and local API status checks. |

## Files and Models

MCA does not request broad storage permissions in the manifest. File and model
selection should happen through Android system pickers or app-managed storage.
Model files, downloaded assets, and generated images remain on device unless
the user exports, shares, or deletes them.

## Cloud Requests

When a cloud chat or image engine is selected, prompts and request parameters
are sent to the provider endpoint configured by the user. MCA does not bundle
provider keys or model weights.

## Diagnostics

Diagnostic logs are local files. Review them before sharing because they may
include model names, device information, prompts, generated text, or provider
error messages.
