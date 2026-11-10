# tools/api.ps1 - calling the local backend by hand from PowerShell, with a session and the CSRF token.
# Used for the manual test of step 9 with the Stripe CLI (docs/studio/32-webhook.md, part 13.1).
#
# Load it once in the terminal:   . .\tools\api.ps1
# Then:                           Api admin POST /api/public/auth/login-code '{"email": "you@example.com"}'
#                                 Api anna GET /api/panel/settings
#
# One cookie jar per person (admin, anna, maria...), in %TEMP%: each person acts only with their own session.
# Every call reads the XSRF-TOKEN cookie and sends it back in X-XSRF-TOKEN (D-051).
# The JSON body goes through a file: Windows PowerShell 5.1 breaks the double quotes of arguments passed to an
# external program such as curl.exe.
# curl.exe (8.x, shipped with Windows) sends the Secure session cookie to localhost over plain http.

function Api {
    param([string]$who, [string]$method, [string]$path, [string]$body)
    $jar = "$env:TEMP\posture-$who.txt"
    if (-not (Test-Path $jar)) { curl.exe -s -o NUL -c $jar http://localhost:8080/api/public/practitioners }
    $token = (Select-String -Path $jar -Pattern "XSRF-TOKEN\s+(\S+)").Matches[0].Groups[1].Value
    $a = @('-s', '-b', $jar, '-c', $jar, '-X', $method, "http://localhost:8080$path",
           '-H', "X-XSRF-TOKEN: $token", '-w', "`n[HTTP %{http_code}]`n")
    if ($body) {
        $file = "$env:TEMP\posture-body.json"
        [System.IO.File]::WriteAllText($file, $body)
        $a += @('-H', 'Content-Type: application/json', '--data-binary', "@$file")
    }
    & curl.exe @a
}

# forget the session of a person (a new sign-in starts from a new jar)
function ApiForget {
    param([string]$who)
    Remove-Item "$env:TEMP\posture-$who.txt" -ErrorAction SilentlyContinue
}
