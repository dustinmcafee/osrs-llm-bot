# Test LM Studio API Server
# Run this after starting the LM Studio server to verify everything works.
#
# Usage: .\test_server.ps1

$baseUrl = "http://localhost:1234/v1"

Write-Host "Testing LM Studio API Server..." -ForegroundColor Cyan
Write-Host ""

# Test 1: Check server is running
Write-Host "1. Checking server health..." -ForegroundColor Yellow
try {
    $models = Invoke-RestMethod -Uri "$baseUrl/models" -Method Get
    Write-Host "   Server is running. Available models:" -ForegroundColor Green
    foreach ($m in $models.data) {
        Write-Host "   - $($m.id)" -ForegroundColor White
    }
} catch {
    Write-Host "   ERROR: Server not responding at $baseUrl" -ForegroundColor Red
    Write-Host "   Make sure LM Studio is running and the server is started." -ForegroundColor Red
    exit 1
}

Write-Host ""

# Test 2: Basic OSRS knowledge question
Write-Host "2. Testing OSRS knowledge..." -ForegroundColor Yellow
$body = @{
    messages = @(
        @{
            role = "system"
            content = "You are an expert on Old School RuneScape. Answer concisely."
        },
        @{
            role = "user"
            content = "What is the best way to train Mining from level 1 to 15?"
        }
    )
    temperature = 0.7
    max_tokens = 300
} | ConvertTo-Json -Depth 5

try {
    $response = Invoke-RestMethod -Uri "$baseUrl/chat/completions" `
        -Method Post -Body $body -ContentType "application/json"
    Write-Host "   Response:" -ForegroundColor Green
    Write-Host "   $($response.choices[0].message.content)" -ForegroundColor White
} catch {
    Write-Host "   ERROR: $($_.Exception.Message)" -ForegroundColor Red
}

Write-Host ""

# Test 3: Bot-style action generation
Write-Host "3. Testing bot action generation..." -ForegroundColor Yellow
$body = @{
    messages = @(
        @{
            role = "system"
            content = "You are an OSRS bot controller. Analyze the game state and respond with a JSON array of actions."
        },
        @{
            role = "user"
            content = @"
[GAME STATE]
Location: Lumbridge (x=3222, y=3218, plane=0)
HP: 10/10
Inventory (1/28): [Bronze axe x1]
Nearby objects: Tree (2 tiles N), Tree (3 tiles W), Oak tree (8 tiles E)
Nearby NPCs: Goblin (lvl 2, 5 tiles S)
Animation: IDLE
Task: Chop regular trees to train Woodcutting
"@
        }
    )
    temperature = 0.3
    max_tokens = 500
} | ConvertTo-Json -Depth 5

try {
    $response = Invoke-RestMethod -Uri "$baseUrl/chat/completions" `
        -Method Post -Body $body -ContentType "application/json"
    Write-Host "   Response:" -ForegroundColor Green
    Write-Host "   $($response.choices[0].message.content)" -ForegroundColor White
} catch {
    Write-Host "   ERROR: $($_.Exception.Message)" -ForegroundColor Red
}

Write-Host ""

# Test 4: Measure latency
Write-Host "4. Measuring response latency..." -ForegroundColor Yellow
$body = @{
    messages = @(
        @{ role = "user"; content = "What level to cut yew trees?" }
    )
    temperature = 0
    max_tokens = 50
} | ConvertTo-Json -Depth 5

$sw = [System.Diagnostics.Stopwatch]::StartNew()
try {
    $response = Invoke-RestMethod -Uri "$baseUrl/chat/completions" `
        -Method Post -Body $body -ContentType "application/json"
    $sw.Stop()

    $tokensPerSec = $response.usage.completion_tokens / ($sw.ElapsedMilliseconds / 1000)
    Write-Host "   Latency: $($sw.ElapsedMilliseconds)ms" -ForegroundColor Green
    Write-Host "   Tokens: $($response.usage.completion_tokens) completion, $($response.usage.prompt_tokens) prompt" -ForegroundColor White
    Write-Host "   Speed: $([math]::Round($tokensPerSec, 1)) tokens/sec" -ForegroundColor White
} catch {
    Write-Host "   ERROR: $($_.Exception.Message)" -ForegroundColor Red
}

Write-Host ""
Write-Host "All tests complete!" -ForegroundColor Cyan
