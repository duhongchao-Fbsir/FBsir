# Resolve Docker CLI: PATH, Docker Desktop default paths, then where.exe / Podman
function Get-DockerExecutable {
    $cmd = Get-Command docker -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }

    $candidates = @()
    if ($env:ProgramFiles) {
        $candidates += (Join-Path $env:ProgramFiles 'Docker\Docker\resources\bin\docker.exe')
    }
    $pf86 = [Environment]::GetEnvironmentVariable('ProgramFiles(x86)')
    if ($pf86) {
        $candidates += (Join-Path $pf86 'Docker\Docker\resources\bin\docker.exe')
    }
    $local = $env:LOCALAPPDATA
    if ($local) {
        $candidates += (Join-Path $local 'Programs\Docker\Docker\resources\bin\docker.exe')
    }

    foreach ($p in $candidates) {
        if ($p -and (Test-Path -LiteralPath $p)) {
            return $p
        }
    }

    if ($env:Path) {
        foreach ($segment in $env:Path -split ';') {
            if (-not $segment) { continue }
            $p = Join-Path $segment.TrimEnd('\') 'docker.exe'
            if (Test-Path -LiteralPath $p) { return $p }
        }
    }

    $podman = Get-Command podman -ErrorAction SilentlyContinue
    if ($podman) { return $podman.Source }

    return $null
}
