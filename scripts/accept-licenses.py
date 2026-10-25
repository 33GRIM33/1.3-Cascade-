import subprocess
import os

sdk_dir = os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk")
sdkmanager = os.path.join(sdk_dir, "cmdline-tools", "latest", "bin", "sdkmanager.bat")

env = os.environ.copy()
env["JAVA_HOME"] = r"C:\Users\laksh\AppData\Local\JDownloader 2\jre"
env["PATH"] = os.path.join(env["JAVA_HOME"], "bin") + os.pathsep + env.get("PATH", "")

proc = subprocess.Popen([sdkmanager, "--licenses"], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, env=env)
out, _ = proc.communicate(input=b"y\n" * 100)
print(out.decode("utf-8", errors="replace")[-300:])
