## POST http://localhost:34787/echo → 200 OK

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
date: Tue, 14 Apr 2026 13:52:06 GMT
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
curl 'http://localhost:34787/echo' \
  -X POST \
  -d '{
  "name": "Eve",
  "action": "create"
}'
```
