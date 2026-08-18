## POST http://localhost:44901/echo → 200 OK

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
date: Tue, 18 Aug 2026 06:15:50 GMT
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
curl 'http://localhost:44901/echo' \
  -X POST \
  -d '{
  "name": "Eve",
  "action": "create"
}'
```
