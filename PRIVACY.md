# Privacy

MCA is designed as a local-first Android AI workspace, but it can also connect
to user-configured cloud providers.

## Local Processing

When using local chat or local image engines, inference runs on the Android
device. Model files stay in user-managed local storage unless the user exports
or deletes them.

## Cloud Processing

When using a cloud chat or cloud image engine, prompts and request parameters
are sent to the configured provider endpoint. Provider retention and training
policies are controlled by that provider, not by MCA.

## API Keys

Cloud API keys are stored on device using Android Keystore-backed encryption.
The repository does not contain API keys.

## Logs

MCA can create local diagnostic logs for runtime metrics, benchmarks, and agent
recommendations. Review logs before sharing them publicly because they may
contain model names, device information, prompts, or generated text.

## Model Files

MCA does not distribute model weights. Users are responsible for the licenses
and privacy implications of any model files they import or download.
