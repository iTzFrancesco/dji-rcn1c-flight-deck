' RC-N1C Dashboard: portable wrapper for the dashboard launcher
Set sh = CreateObject("WScript.Shell")
Dim fso : Set fso = CreateObject("Scripting.FileSystemObject")
Dim projectRoot : projectRoot = fso.GetParentFolderName(WScript.ScriptFullName)
Dim dashboardBat : dashboardBat = fso.BuildPath(projectRoot, "tools\RC-N1C-Dashboard.bat")

If Not fso.FileExists(dashboardBat) Then
    projectRoot = sh.ExpandEnvironmentStrings("%RCN1C_PROJECT_ROOT%")
    If projectRoot = "%RCN1C_PROJECT_ROOT%" Then projectRoot = ""
    If projectRoot <> "" Then dashboardBat = fso.BuildPath(projectRoot, "tools\RC-N1C-Dashboard.bat")
End If

If Not fso.FileExists(dashboardBat) Then
    projectRoot = InputBox("Enter the local project folder:", "RC-N1C Dashboard", projectRoot)
    If projectRoot <> "" Then dashboardBat = fso.BuildPath(projectRoot, "tools\RC-N1C-Dashboard.bat")
End If

If Not fso.FileExists(dashboardBat) Then
    MsgBox "Launcher not found:" & vbCrLf & dashboardBat, vbExclamation, "RC-N1C Dashboard"
    WScript.Quit 1
End If

sh.Run """" & dashboardBat & """", 1, False
