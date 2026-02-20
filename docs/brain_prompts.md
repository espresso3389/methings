# Brain Prompt Pack

This file contains starter prompts for me.things so it can use local APIs, filesystem, and command-line tools consistently.

The agent has a built-in default system prompt. The prompts below can be used to customize behavior.

## 1) Recommended System Prompt
Use this as `system_prompt` in `POST /brain/config`.

```text
You are "methings" running on Android inside a constrained local environment.
Return strict JSON only with keys:
- responses: array of strings
- actions: array of objects

Use short, practical responses. Then propose actions.
Available action types:
- shell_exec: {"type":"shell_exec","cmd":"python|pip|curl","args":"...","cwd":"/..."}
- write_file: {"type":"write_file","path":"relative/path.py","content":"..."}
- tool_invoke: {"type":"tool_invoke","tool":"filesystem|shell","args":{...},"request_id":"optional","detail":"optional"}
- sleep: {"type":"sleep","seconds":1}

Rules:
1. Never use shell commands except python, pip, curl.
2. Keep all file writes under user root.
3. Prefer minimal actions; do not install packages unless required.
4. SSH/SCP can be proposed for remote interaction, but do not execute ssh/scp unless supported by available actions.
5. If requirements are ambiguous, ask one concise clarifying response and avoid risky actions.
6. Do not claim success before command output confirms success.
7. Keep scripts small and test quickly after writing.
8. If a tool action returns permission_required, explain what permission to approve and stop.
9. If search results look weak (especially for non-English queries), use `web_search` with provider "brave" if configured, otherwise rewrite the query in English and retry.
```

## 2) Setup Prompt (One-Time)
Send this as first user chat.

```text
Initialize yourself for this device.
1) Check python version.
2) Check pip is available.
3) Create /user/apps/brain_scratch/ and write a tiny hello script.
4) Run the script and report result.
Use minimal actions.
```

## 3) API Exploration Prompt

```text
Inspect available local capabilities through existing APIs and summarize what you can do now.
Focus on brain endpoints, shell_exec behavior, and permission-gated tool calls.
Do not install anything.
```

## 4) Filesystem Workflow Prompt

```text
Create a new project at /user/apps/todo_bot.
Write:
- main.py
- requirements.txt
Then run a quick syntax check with python.
If dependencies are missing, propose install actions but do not execute installs until I confirm.
```

## 5) CLI Tooling Prompt

```text
Prepare a clean environment workflow for this project.
Prefer venv + pip.
Generate exact commands as shell_exec actions and verify each step.
```

## 6) Event-Driven Automation Prompt

```text
When you receive event name "wifi_connected", append a timestamped line to /user/apps/brain_scratch/events.log.
If the file path does not exist, create it first.
Then respond with a one-line status summary.
```

## 7) Safe Package Install Prompt

```text
I want package: requests.
Before installing, explain why it is needed and what script requires it.
Then install using minimal steps and verify import with python -c.
```

## 8) Debug Prompt

```text
The latest task failed.
Read your recent messages and produce:
1) root cause hypothesis
2) one minimal fix plan
3) exact next actions
Keep it concise.
```

## 9) Optional SSH/SCP Prompt

```text
If a task involves another device, propose an SSH/SCP plan:
1) how to fetch this device client public key
2) where to place it on the remote device authorized_keys
3) how to test ssh login and scp file transfer
Do not execute ssh/scp commands unless available actions support them.

Notes:
- Interactive `ssh user@host` (no command) is not supported in the app shell (no PTY). Use:
  `ssh user@host <command>`
- `scp` can stall against some OpenSSH-for-Windows targets. Prefer these shell commands instead:
  - `put <local_file> <user@host:remote_path_or_dir>`
  - `get <user@host:remote_file> <local_path>`
```

## Get Client Public Key (for authorized_keys)
Use local API:

```bash
curl -sS http://127.0.0.1:33389/ssh/status
```

Read these fields:
- `client_key_public`: full public key line to paste into remote `~/.ssh/authorized_keys`
- `client_key_fingerprint`: fingerprint for verification

Typical remote setup:
```bash
mkdir -p ~/.ssh
chmod 700 ~/.ssh
echo '<client_key_public>' >> ~/.ssh/authorized_keys
chmod 600 ~/.ssh/authorized_keys
```

## Apply Prompt via API

```bash
curl -sS -X POST http://127.0.0.1:33389/brain/config \
  -H 'Content-Type: application/json' \
  -d '{"system_prompt":"<PASTE_SYSTEM_PROMPT_HERE>"}'
```
