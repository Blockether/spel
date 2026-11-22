## POST http://localhost:42181/echo → 200 OK

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
date: Sun, 22 Nov 2026 20:21:01 GMT
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
curl 'http://localhost:42181/echo' \
  -X POST \
  -d '{
  "name": "Eve",
  "action": "create"
}'
```
