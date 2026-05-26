@echo off
echo Building Property Manager Inbox Supervisor...
pyinstaller --onefile --noconsole --name "InboxSupervisor" main.py
echo Build complete! Executable is in the dist\ folder.
pause
