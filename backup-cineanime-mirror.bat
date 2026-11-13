@echo off
echo ===================================================
echo CineAnime 1-Click Multi-Mirror Synchronizer
echo ===================================================
echo [1/3] Pushing to GitHub & Codeberg remotes...
cd /d "C:\Users\velaz\Documents\antigravity\cineanime"
git push origin main
git push codeberg main

echo [2/3] Mirroring static build into Velazquez Lab...
xcopy /Y /E /I "C:\Users\velaz\Documents\antigravity\cineanime\index.html" "C:\Users\velaz\Documents\antigravity\velazquez-lab\cineanime\"
xcopy /Y /E /I "C:\Users\velaz\Documents\antigravity\cineanime\about.html" "C:\Users\velaz\Documents\antigravity\velazquez-lab\cineanime\"
xcopy /Y /E /I "C:\Users\velaz\Documents\antigravity\cineanime\servers.json" "C:\Users\velaz\Documents\antigravity\velazquez-lab\cineanime\"
xcopy /Y /E /I "C:\Users\velaz\Documents\antigravity\cineanime\sw.js" "C:\Users\velaz\Documents\antigravity\velazquez-lab\cineanime\"
xcopy /Y /E /I "C:\Users\velaz\Documents\antigravity\cineanime\manifest.webmanifest" "C:\Users\velaz\Documents\antigravity\velazquez-lab\cineanime\"
xcopy /Y /E /I "C:\Users\velaz\Documents\antigravity\cineanime\robots.txt" "C:\Users\velaz\Documents\antigravity\velazquez-lab\cineanime\"
xcopy /Y /E /I "C:\Users\velaz\Documents\antigravity\cineanime\icon*.png" "C:\Users\velaz\Documents\antigravity\velazquez-lab\cineanime\"
xcopy /Y /E /I "C:\Users\velaz\Documents\antigravity\cineanime\icon.svg" "C:\Users\velaz\Documents\antigravity\velazquez-lab\cineanime\"
xcopy /Y /E /I "C:\Users\velaz\Documents\antigravity\cineanime\vendor" "C:\Users\velaz\Documents\antigravity\velazquez-lab\cineanime\vendor\"

echo [3/3] Pushing Velazquez Lab mirror...
cd /d "C:\Users\velaz\Documents\antigravity\velazquez-lab"
git add cineanime
git commit -m "sync(cineanime): mirror update"
git push origin main

echo ===================================================
echo Done! All 3 live mirror backups are up to date!
echo ===================================================
pause
