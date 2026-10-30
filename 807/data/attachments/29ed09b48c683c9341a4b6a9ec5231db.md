## POST http://localhost:34213/echo → 200 OK

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
date: Fri, 30 Oct 2026 12:21:28 GMT
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
curl 'http://localhost:34213/echo' \
  -X POST \
  -d '{
  "name": "Eve",
  "action": "create"
}'
```
