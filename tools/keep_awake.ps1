# Keeps Windows from sleeping while the experiment script with Windows process id
# -ParentPid is alive. Started in the background by tools/common.sh keep_awake (Git Bash).
# Uses SetThreadExecutionState(ES_CONTINUOUS | ES_SYSTEM_REQUIRED) on this thread, which
# stays alive in the wait loop; the request ends when this process exits.
# Written for Windows PowerShell 5.1 (also runs on PowerShell 7).
param([Parameter(Mandatory = $true)][int]$ParentPid)

$signature = '[DllImport("kernel32.dll")] public static extern uint SetThreadExecutionState(uint esFlags);'
$kernel = Add-Type -MemberDefinition $signature -Name 'Power' -Namespace 'WeltKeepAwake' -PassThru
$ES_CONTINUOUS = [uint32]2147483648
$ES_SYSTEM_REQUIRED = [uint32]1
$previous = $kernel::SetThreadExecutionState($ES_CONTINUOUS -bor $ES_SYSTEM_REQUIRED)
if ($previous -eq 0) {
    Write-Warning "keep_awake: SetThreadExecutionState failed; the machine may sleep during the run."
    exit 1
}
while (Get-Process -Id $ParentPid -ErrorAction SilentlyContinue) {
    Start-Sleep -Seconds 30
}
$null = $kernel::SetThreadExecutionState($ES_CONTINUOUS)
