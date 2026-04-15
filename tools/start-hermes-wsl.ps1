# Start Hermes Gateway in WSL2 Ubuntu (recommended; Windows native "hermes gateway run" fails PID checks).
# Prerequisites: WSL Ubuntu, venv /root/hermes-venv, pip install -e /mnt/d/u3wv2/.dev/hermes-agent
# Health: curl.exe http://127.0.0.1:8642/health
param(
    [switch]$Background
)
$ErrorActionPreference = "Stop"
$run = "export API_SERVER_ENABLED=true; . /root/hermes-venv/bin/activate; cd /mnt/d/u3wv2/.dev/hermes-agent; "
if ($Background) {
    wsl -d Ubuntu -u root -e bash -lc ($run + "nohup hermes gateway run >> /tmp/hermes-gw.out 2>&1 & sleep 3; tail -30 /tmp/hermes-gw.out")
    Write-Host "Check: curl.exe http://127.0.0.1:8642/health"
} else {
    wsl -d Ubuntu -u root -e bash -lc ($run + "exec hermes gateway run")
}
