# 解析本机可调用的 Maven 可执行文件（mvn / mvn.cmd），供其它 tools 脚本点源复用。
# 用法（在调用方脚本内）:
#   $repoRoot = Split-Path -Parent $PSScriptRoot
#   . (Join-Path $PSScriptRoot 'Resolve-Maven.ps1')
#   $mvnExe = Get-MavenExecutable -RepoRoot $repoRoot
#   if (-not $mvnExe) { Write-Error '未找到 Maven'; exit 1 }
#   & $mvnExe -version
#
# 可覆盖: 环境变量 WXFBSIR_MVN 指向 mvn.cmd 的完整路径（优先于自动探测）。

function Get-MavenExecutable {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $false)]
        [string]$RepoRoot = ""
    )

    # 1) 显式覆盖
    $explicit = $env:WXFBSIR_MVN
    if (-not [string]::IsNullOrWhiteSpace($explicit) -and (Test-Path -LiteralPath $explicit.Trim())) {
        return $explicit.Trim()
    }

    # 2) PATH 中的 mvn / mvn.cmd
    $cmd = Get-Command mvn -ErrorAction SilentlyContinue
    if ($cmd -and $cmd.Source) {
        if (Test-Path -LiteralPath $cmd.Source) {
            return $cmd.Source
        }
    }

    # 3) MAVEN_HOME / M2_HOME（当前进程 + 用户 + 机器）
    $homeCandidates = @(
        $env:MAVEN_HOME,
        $env:M2_HOME,
        [Environment]::GetEnvironmentVariable('MAVEN_HOME', 'User'),
        [Environment]::GetEnvironmentVariable('MAVEN_HOME', 'Machine'),
        [Environment]::GetEnvironmentVariable('M2_HOME', 'User'),
        [Environment]::GetEnvironmentVariable('M2_HOME', 'Machine')
    ) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -Unique

    foreach ($h in $homeCandidates) {
        $t = $h.Trim()
        foreach ($name in @('mvn.cmd', 'mvn')) {
            $p = Join-Path $t "bin\$name"
            if (Test-Path -LiteralPath $p) { return $p }
        }
    }

    # 4) where.exe（部分环境 Get-Command 不可用但 where 能找到）
    try {
        $whereOut = & where.exe mvn 2>$null
        if ($whereOut) {
            foreach ($line in $whereOut) {
                $w = $line.Trim()
                if ($w -and (Test-Path -LiteralPath $w)) { return $w }
            }
        }
    } catch { }

    # 5) 仓库内便携包（与现有文档/脚本一致）
    if (-not [string]::IsNullOrWhiteSpace($RepoRoot) -and (Test-Path -LiteralPath $RepoRoot)) {
        $relBundles = @(
            Join-Path $RepoRoot '.tools\maven-extract\apache-maven-3.9.9\bin\mvn.cmd'
            Join-Path $RepoRoot 'build-tools\apache-maven-3.9.15\bin\mvn.cmd'
        )
        foreach ($b in $relBundles) {
            if (Test-Path -LiteralPath $b) { return $b }
        }
        # 通配：.tools/maven-extract/apache-maven-*/bin/mvn.cmd
        $wildRoots = @(
            (Join-Path $RepoRoot '.tools\maven-extract')
            (Join-Path $RepoRoot 'build-tools')
        )
        foreach ($wr in $wildRoots) {
            if (-not (Test-Path -LiteralPath $wr)) { continue }
            $found = Get-ChildItem -Path $wr -Directory -Filter 'apache-maven-*' -ErrorAction SilentlyContinue |
                Sort-Object Name -Descending |
                ForEach-Object {
                    $c = Join-Path $_.FullName 'bin\mvn.cmd'
                    if (Test-Path -LiteralPath $c) { $c }
                } |
                Select-Object -First 1
            if ($found) { return $found }
        }
    }

    # 6) 常见安装位置 / Scoop / Chocolatey（浅层搜索，避免全盘）
    $extraDirs = @(
        (Join-Path $env:USERPROFILE 'Documents'),
        (Join-Path $env:USERPROFILE 'scoop\apps\maven\current\bin'),
        'C:\Program Files\Maven',
        'C:\Program Files\Apache\Maven',
        (Join-Path $env:ProgramData 'chocolatey\lib\maven')
    ) | Where-Object { $_ -and (Test-Path -LiteralPath $_) }

    foreach ($d in $extraDirs) {
        $hit = Get-ChildItem -Path $d -Filter 'mvn.cmd' -Recurse -ErrorAction SilentlyContinue -Depth 6 |
            Where-Object { $_.FullName -match '[\\/]apache-maven-[^\\/]+[\\/]bin[\\/]mvn\.cmd$' } |
            Sort-Object FullName -Descending |
            Select-Object -First 1
        if ($hit) { return $hit.FullName }
    }

    return $null
}
