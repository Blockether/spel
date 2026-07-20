## POST http://localhost:45439/echo → 200 OK

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
date: Mon, 20 Jul 2026 10:37:14 GMT
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
curl 'http://localhost:45439/echo' \
  -X POST \
  -d '{
  "name": "Eve",
  "action": "create"
}'
```
