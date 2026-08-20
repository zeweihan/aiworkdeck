-- AI WorkDeck Office 插件安装器（用户态 .app，随 DMG 分发）。
-- 把 Contents/Resources/manifest.xml 拷进 Word/Excel/PowerPoint 三容器的 wef sideload 目录。
-- 为什么不用 pkg：macOS 26 起应用容器保护直接拒绝 root 安装脚本写他人容器（EPERM、无弹窗），
-- 只有用户会话内的进程能经「访问其他 App 的数据」授权弹窗拿到写入权（dev-board#68）。
-- 卸载 = 删掉三个 wef 目录里的 aiworkdeck-manifest.xml。

on run
	set manifestPath to POSIX path of (path to resource "manifest.xml")
	set homePath to POSIX path of (path to home folder)
	set hostNames to {"Word", "Excel", "PowerPoint"}
	set bundleIds to {"com.microsoft.Word", "com.microsoft.Excel", "com.microsoft.Powerpoint"}
	set failedHosts to {}
	set failedPaths to {}
	repeat with i from 1 to count of bundleIds
		set wefPath to homePath & "Library/Containers/" & (item i of bundleIds) & "/Data/Documents/wef"
		try
			do shell script "/bin/mkdir -p " & quoted form of wefPath & " && /bin/cp -f " & quoted form of manifestPath & " " & quoted form of (wefPath & "/aiworkdeck-manifest.xml")
		on error
			set end of failedHosts to (item i of hostNames)
			set end of failedPaths to wefPath
		end try
	end repeat
	if (count of failedHosts) is 0 then
		display dialog "安装完成。" & return & return & "完全退出并重新打开 Word / Excel / PowerPoint 后，功能区会出现 AI WorkDeck 按钮。" buttons {"完成"} default button "完成" with title "AI WorkDeck Office 插件"
	else
		set AppleScript's text item delimiters to "、"
		set hostList to failedHosts as text
		set AppleScript's text item delimiters to ""
		set choice to button returned of (display dialog "系统未允许写入 " & hostList & " 的插件目录（macOS 隐私保护）。" & return & return & "可以手动完成：点击「打开目标文件夹」，把随后高亮显示的 manifest.xml 拷进每个打开的文件夹（如果里面没有 wef 文件夹，先新建一个再拷入）。" buttons {"取消", "打开目标文件夹"} default button "打开目标文件夹" with title "AI WorkDeck Office 插件")
		if choice is "打开目标文件夹" then
			repeat with p in failedPaths
				set wefPath to contents of p
				try
					do shell script "/bin/mkdir -p " & quoted form of wefPath
				end try
				-- TCC 拒绝时 wef 可能建不出来，退而打开其 Documents 上级（Finder 有自己的访问权）
				do shell script "/usr/bin/open " & quoted form of wefPath & " || /usr/bin/open " & quoted form of (do shell script "/usr/bin/dirname " & quoted form of wefPath)
			end repeat
			-- 优先高亮 DMG 根目录那份 manifest（用户拖起来直观）；没有再退回包内资源
			set dmgManifest to (do shell script "/usr/bin/dirname " & quoted form of (POSIX path of (path to me))) & "/manifest.xml"
			try
				do shell script "/usr/bin/test -f " & quoted form of dmgManifest
				do shell script "/usr/bin/open -R " & quoted form of dmgManifest
			on error
				do shell script "/usr/bin/open -R " & quoted form of manifestPath
			end try
		end if
	end if
end run
