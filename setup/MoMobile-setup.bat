@echo off
rem Mo Mobile setup: double-click to start the server.
rem It runs setup.ps1 from the Mo Mobile GitHub repo. Read it first if you like:
rem https://github.com/mo-mobile/mo-mobile/blob/main/setup/setup.ps1
powershell -NoProfile -ExecutionPolicy Bypass -Command "irm https://raw.githubusercontent.com/mo-mobile/mo-mobile/main/setup/setup.ps1 | iex"
