<#
.SYNOPSIS
    CI 冒烟测试断言工具：读取指定进程主窗口的 AppUserModelID。

.DESCRIPTION
    通过 Windows Shell 官方接口 SHGetPropertyStoreForWindow 读取窗口属性
    PKEY_AppUserModel_ID —— 与插件写入端互为镜像（读 = 插件写）。
    用于在 GitHub Windows runner 上验证外部应用取消分组功能真实生效：
    插件对外部应用窗口写入的 AUMID 形如 TBG.X.<pid>.<hwnd>。

.PARAMETER ProcessNames
    目标进程名（不含 .exe），如 notepad。

.PARAMETER ExpectedPrefix
    期望的 AUMID 前缀，默认 "TBG.X."。

.PARAMETER MinWindows
    期望至少匹配到的窗口数，默认 2。

.PARAMETER TimeoutSeconds
    轮询超时（秒），默认 60（覆盖 2 秒扫描周期 + JNA 迟到）。

.EXAMPLE
    ./taskbar-aumid-check.ps1 -ProcessNames notepad -MinWindows 2
#>
param(
    [Parameter(Mandatory = $true)][string[]]$ProcessNames,
    [string]$ExpectedPrefix = "TBG.X.",
    [int]$MinWindows = 2,
    [int]$TimeoutSeconds = 60
)

$ErrorActionPreference = 'Stop'

Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;

public static class AumidReader
{
    [DllImport("shell32.dll", CharSet = CharSet.Unicode, PreserveSig = false)]
    private static extern void SHGetPropertyStoreForWindow(IntPtr hwnd, ref Guid riid, [MarshalAs(UnmanagedType.Interface)] out IPropertyStore ppv);

    [ComImport, Guid("886D8EEB-8CF2-4446-8D02-CDBA1DBDCF99"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IPropertyStore
    {
        int GetCount(out uint cProps);
        int GetAt(uint iProp, out PROPERTYKEY pkey);
        int GetValue(ref PROPERTYKEY key, out PROPVARIANT pv);
        int SetValue(ref PROPERTYKEY key, ref PROPVARIANT propvar);
        int Commit();
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct PROPERTYKEY
    {
        public Guid fmtid;
        public uint pid;
    }

    [StructLayout(LayoutKind.Explicit, Size = 24)]
    private struct PROPVARIANT
    {
        [FieldOffset(0)] public ushort vt;
        [FieldOffset(8)] public IntPtr pointerValue;
    }

    [DllImport("ole32.dll")]
    private static extern int PropVariantClear(ref PROPVARIANT pvar);

    private static readonly Guid IID_IPropertyStore = new Guid("886D8EEB-8CF2-4446-8D02-CDBA1DBDCF99");

    public static string GetWindowAumid(IntPtr hwnd)
    {
        try
        {
            Guid iid = IID_IPropertyStore;
            IPropertyStore store;
            SHGetPropertyStoreForWindow(hwnd, ref iid, out store);
            if (store == null) return null;
            try
            {
                PROPERTYKEY key = new PROPERTYKEY();
                key.fmtid = new Guid("9F4C2855-EE22-4C80-9C1A-1D6CE9F6D1CE"); // PKEY_AppUserModel_ID 官方 FMTID（原 GUID 写错导致恒读空值）
                key.pid = 5;
                PROPVARIANT v;
                int hr = store.GetValue(ref key, out v);
                string result = null;
                if (hr == 0 && v.vt == 31) // VT_LPWSTR
                {
                    result = Marshal.PtrToStringUni(v.pointerValue);
                }
                PropVariantClear(ref v);
                return result;
            }
            finally
            {
                Marshal.ReleaseComObject(store);
            }
        }
        catch (Exception)
        {
            return null;
        }
    }
}
"@

function Get-TargetWindows
{
    Get-Process -Name $ProcessNames -ErrorAction SilentlyContinue |
        Where-Object { $_.MainWindowHandle -ne 0 } |
        ForEach-Object { [pscustomobject]@{ Pid = $_.Id; Handle = $_.MainWindowHandle; Title = $_.MainWindowTitle } }
}

# 基线自证：读出所有可见顶层窗口的 AUMID。
# IDE 自身窗口此时应已带 TBG.IDEA.*（插件事件路径写入，idea.log 有实证）；
# 若基线中 java 窗口 AUMID 为空 → 读取器自身有问题；若能读出 → 插件 sweep 有问题。
Write-Host "[baseline] all visible top-level windows (aumid via SHGetPropertyStoreForWindow):"
Get-Process | Where-Object { $_.MainWindowHandle -ne 0 } | ForEach-Object {
    $a = [AumidReader]::GetWindowAumid([IntPtr]$_.MainWindowHandle)
    Write-Host ("  pid={0} proc={1} title='{2}' aumid='{3}'" -f $_.Id, $_.ProcessName, $_.MainWindowTitle, $a)
}

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$attempt = 0
do {
    $attempt++
    $windows = @(Get-TargetWindows)
    $readings = foreach ($w in $windows) {
        [pscustomobject]@{
            Pid   = $w.Pid
            Title = $w.Title
            Aumid = [AumidReader]::GetWindowAumid([IntPtr]$w.Handle)
        }
    }
    $matched = @($readings | Where-Object { $_.Aumid -and $_.Aumid.StartsWith($ExpectedPrefix) })
    Write-Host ("[attempt {0}] windows={1} matched={2}" -f $attempt, $readings.Count, $matched.Count)
    foreach ($r in $readings) {
        Write-Host ("  pid={0} title='{1}' aumid='{2}'" -f $r.Pid, $r.Title, $r.Aumid)
    }
    if ($matched.Count -ge $MinWindows -and $matched.Count -eq $readings.Count -and $readings.Count -ge $MinWindows) {
        Write-Host "SMOKE-PASS: $matched.Count window(s) carry expected AUMID prefix '$ExpectedPrefix'"
        exit 0
    }
    Start-Sleep -Seconds 2
} while ((Get-Date) -lt $deadline)

Write-Host "SMOKE-FAIL: expected >= $MinWindows windows with AUMID prefix '$ExpectedPrefix' within ${TimeoutSeconds}s"
Write-Host "Final readings:"
foreach ($r in $readings) {
    Write-Host ("  pid={0} title='{1}' aumid='{2}'" -f $r.Pid, $r.Title, $r.Aumid)
}
exit 1
