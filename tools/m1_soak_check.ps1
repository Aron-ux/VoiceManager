param(
    [string]$AdbPath = "C:\Users\Aron\AppData\Local\Android\Sdk\platform-tools\adb.exe",
    [string]$PackageName = "com.company.vehiclevoice",
    [int]$DurationSeconds = 1800,
    [int]$IntervalSeconds = 60,
    [string]$OutputPath = "C:\Users\Aron\Documents\sherpa\m1-soak-result.txt"
)

$ErrorActionPreference = "Continue"

$startedAt = Get-Date
$deadline = $startedAt.AddSeconds($DurationSeconds)
$samples = New-Object System.Collections.Generic.List[string]
$failed = $false

$samples.Add("M1 soak check started: $($startedAt.ToString('yyyy-MM-dd HH:mm:ss'))")
$samples.Add("DurationSeconds=$DurationSeconds IntervalSeconds=$IntervalSeconds Package=$PackageName")

while ((Get-Date) -lt $deadline) {
    $now = Get-Date
    $appPid = (& $AdbPath shell pidof $PackageName 2>&1) -join " "
    $services = (& $AdbPath shell dumpsys activity services $PackageName 2>&1) -join "`n"
    $notifications = (& $AdbPath shell dumpsys notification --noredact 2>&1) -join "`n"

    $hasPid = -not [string]::IsNullOrWhiteSpace($appPid)
    $hasService = ($services -match [regex]::Escape("$PackageName/.VoiceForegroundService")) -and
        ($services -match "isForeground=true")
    $hasNotification = ($notifications -match [regex]::Escape("pkg=$PackageName")) -and
        ($notifications -match "FOREGROUND_SERVICE")

    $line = "{0} pid={1} foregroundService={2} foregroundNotification={3}" -f `
        $now.ToString("HH:mm:ss"), $hasPid, $hasService, $hasNotification
    $samples.Add($line)
    $samples | Set-Content -Path $OutputPath -Encoding UTF8

    if (-not ($hasPid -and $hasService -and $hasNotification)) {
        $failed = $true
        break
    }

    Start-Sleep -Seconds $IntervalSeconds
}

$endedAt = Get-Date
$samples.Add("M1 soak check ended: $($endedAt.ToString('yyyy-MM-dd HH:mm:ss'))")
$samples.Add("Result=$($(if ($failed) { 'FAILED' } else { 'PASSED' }))")
$samples | Set-Content -Path $OutputPath -Encoding UTF8
