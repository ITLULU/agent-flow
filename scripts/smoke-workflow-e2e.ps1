$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$base = "http://localhost:8080"

$dsl = @'
{"name":"P1运行时验证链","trigger":{"type":"manual"},
 "nodes":[{"id":"a","type":"core.echo","params":{"message":"hello"}},
          {"id":"b","type":"core.echo","params":{"message":"world"}}],
 "edges":[{"from":"a","to":"b"}]}
'@

$created = Invoke-RestMethod -Uri "$base/api/workflows" -Method Post -ContentType "application/json" -Body $dsl
Write-Host ("1) 保存: code={0} workflowId={1}" -f $created.code, $created.data.workflowId)

$wid = $created.data.workflowId
$trig = Invoke-RestMethod -Uri "$base/api/workflows/$wid/executions" -Method Post -ContentType "application/json" -Body '{}'
Write-Host ("2) 触发: executionId={0} traceId={1}" -f $trig.data.executionId, $trig.data.traceId)

Start-Sleep -Milliseconds 600
$exec = Invoke-RestMethod -Uri "$base/api/executions/$($trig.data.executionId)"
$nodes = ($exec.data.nodes | ForEach-Object { "$($_.nodeId)/$($_.nodeType)/$($_.status)/$($_.attempts)次/$($_.durationMs)ms" }) -join " | "
Write-Host ("3) 执行详情: status={0} traceId贯穿={1}" -f $exec.data.status, ($exec.data.traceId -eq $trig.data.traceId))
Write-Host ("   节点日志: " + $nodes)
Write-Host ("   输出b: " + ($exec.data.nodes[1].output | ConvertTo-Json -Compress))

try {
  Invoke-RestMethod -Uri "$base/api/workflows" -Method Post -ContentType "application/json" -Body '{"name":"环","nodes":[{"id":"x","type":"core.echo"},{"id":"y","type":"core.echo"}],"edges":[{"from":"x","to":"y"},{"from":"y","to":"x"}]}'
} catch {
  $r = $_.ErrorDetails.Message
  Write-Host ("4) 环拒绝: " + $r)
}
