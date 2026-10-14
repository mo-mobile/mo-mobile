# Mo Mobile setup for Windows.
#
# Starts the Mo Mobile server in Docker and opens the setup page. Safe to run
# again: it updates the server and keeps the card data.
#
# What it does:
#   1. Checks that Docker Desktop is installed and running
#   2. Downloads the server image (ghcr.io/mo-mobile/mo-mobile)
#   3. Starts it on port 8420, restarting automatically, card data in a Docker volume
#   4. Asks Windows (with an admin prompt) to let your phone reach port 8420
#   5. Opens the setup page

# Native commands report errors through $LASTEXITCODE; 'Stop' would turn
# Docker's normal stderr output into errors on Windows PowerShell 5.1.
$ErrorActionPreference = 'Continue'
$Image = 'ghcr.io/mo-mobile/mo-mobile:latest'
$Name  = 'mo-mobile'
$Port  = 8420

function Fail($msg) {
    Write-Host ""
    Write-Host $msg -ForegroundColor Red
    Write-Host ""
    Read-Host "Press Enter to close"
    exit 1
}

Write-Host "Mo Mobile setup" -ForegroundColor Cyan
Write-Host ""

# 1. Docker
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Fail "Docker Desktop isn't installed. Get it from https://www.docker.com/products/docker-desktop/, open it once, then run this again."
}
docker info *> $null
if ($LASTEXITCODE -ne 0) {
    Fail "Docker Desktop isn't running. Open it, wait until it says it's running, then run this again."
}

# This computer's address on the local network, for the phone.
$ip = (Get-NetIPConfiguration |
       Where-Object { $_.IPv4DefaultGateway -and $_.NetAdapter.Status -eq 'Up' } |
       Select-Object -First 1).IPv4Address.IPAddress
if (-not $ip) { $ip = 'localhost' }
$url = "http://${ip}:$Port"

# 2. Image
Write-Host "Downloading the server..."
docker pull $Image
if ($LASTEXITCODE -ne 0) { Fail "Couldn't download $Image. Check your internet connection and try again." }

# 3. Container (replaces an older one, keeps the mo-mobile-data volume)
docker rm -f $Name *> $null
docker run -d --name $Name --restart unless-stopped `
    -p "${Port}:8000" -v mo-mobile-data:/app/store `
    -e "MO_PUBLIC_URL=$url" $Image | Out-Null
if ($LASTEXITCODE -ne 0) { Fail "The server didn't start. Is something else using port $Port?" }

# 4. Firewall, so the phone can reach it
if (-not (Get-NetFirewallRule -DisplayName 'MoMobile' -ErrorAction SilentlyContinue)) {
    Write-Host "Windows will ask for permission to let your phone reach the server (port $Port)."
    try {
        Start-Process powershell -Verb RunAs -Wait -ArgumentList `
            "-NoProfile -Command New-NetFirewallRule -DisplayName MoMobile -Direction Inbound -Protocol TCP -LocalPort $Port -Action Allow -Profile Private"
    } catch {
        Write-Host "Skipped. If your phone can't reach the server later, run this again and click Yes." -ForegroundColor Yellow
    }
}

# 5. Done
Write-Host ""
Write-Host "The server is running at $url" -ForegroundColor Green
Write-Host "Opening the setup page. It shows the card download progress and a QR code for your phone."
Write-Host ""
Write-Host "Tip: in Docker Desktop's settings, turn on 'Start Docker Desktop when you sign in'"
Write-Host "so the server comes back after a restart."
Start-Process "$url/setup"
Write-Host ""
Read-Host "Press Enter to close"
