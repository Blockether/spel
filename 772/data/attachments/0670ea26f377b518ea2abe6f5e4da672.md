## POST http://localhost:41313/echo → 200 OK

### Request Body
```json
{
  "name": "Eve",
  "action": "create"
}
```

### Response Headers
```
content-length: 72
content-type: application/json
date: Sat, 03 Oct 2026 07:03:23 GMT
```

### Response Body
```json
{
  "method": "POST",
  "path": "/echo",
  "body": {
    "name": "Eve",
    "action": "create"
  }
}
```

### cURL
```bash
curl 'http://localhost:41313/echo' \
  -X POST \
  -d '{
  "name": "Eve",
  "action": "create"
}'
```
