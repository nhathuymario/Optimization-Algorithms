param (
    [string]$envFile = ".env"
)

Write-Host "Loading environment variables from $envFile..." -ForegroundColor Cyan

if (Test-Path $envFile) {
    Get-Content $envFile | Where-Object { $_ -match '^[a-zA-Z0-9_]+=' } | ForEach-Object {
        $name, $value = $_.Split('=', 2)
        Set-Item -Path "env:$name" -Value $value
    }
    Write-Host "Environment variables loaded successfully." -ForegroundColor Green
} else {
    Write-Host "Warning: $envFile not found. Using default application.properties values." -ForegroundColor Yellow
}

Write-Host "Starting Maven build and execution..." -ForegroundColor Cyan
mvn clean compile exec:java
