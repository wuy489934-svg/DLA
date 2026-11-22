# Security Policy

## Supported versions

`1.3.x` is the current line. Release candidates may change without notice.

## Reporting a vulnerability

Please **do not** open a public issue for security problems.

Use GitHub's private vulnerability reporting on the repository, or contact the maintainer listed in `README.md`. Include:

- affected version/commit
- Android version and device
- reproduction steps
- impact assessment
- whether user data or shell/accessibility access is involved

We will acknowledge within 7 days and aim to ship a fix or mitigation within 30 days.

## Threat model

DLA is a power-user tool. It can:

- read the current screen through Accessibility;
- enumerate installed apps (`QUERY_ALL_PACKAGES`);
- execute shell commands through Shizuku;
- send selected conversation content to the API endpoint configured by the user.

It is **not** a security boundary between the user and a malicious model. Prompt injection from web pages, notifications, or documents can attempt to invoke tools. Mitigations:

- approval mode is the default in the public build;
- full-access mode must be explicitly enabled;
- the notification and Agent UI provide an emergency stop;
- shell commands are executed with the privileges the user granted to Shizuku.

Do not enable full-access mode on a device holding data you would not expose to the model or to a malicious prompt.

## Secrets

- API keys are user-supplied and stored on-device. They must never be committed, pasted into issues, or included in diagnostics.
- Release signing keys and passwords live outside the repository.
- Diagnostics intentionally exclude message bodies; tool names and stack traces are kept for debugging.

