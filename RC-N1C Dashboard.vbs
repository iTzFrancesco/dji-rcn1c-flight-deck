' RC-N1C Dashboard: wrapper per il launcher automatico sul Desktop
Set sh = CreateObject("WScript.Shell")
Dim fso : Set fso = CreateObject("Scripting.FileSystemObject")
Dim projectRoot : projectRoot = "C:\Users\Francesco\OneDrive\Documenti\developer\GitHub\drone-dji-app"
Dim dashboardBat : dashboardBat = fso.BuildPath(projectRoot, "tools\RC-N1C-Dashboard.bat")

If Not fso.FileExists(dashboardBat) Then
    MsgBox "Launcher non trovato:" & vbCrLf & dashboardBat, vbExclamation, "RC-N1C Dashboard"
    WScript.Quit 1
End If

sh.Run """" & dashboardBat & """", 1, False
