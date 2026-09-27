## POST http://localhost:37217/echo → 200 OK

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
date: Sun, 27 Sep 2026 12:53:42 GMT
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
curl 'http://localhost:37217/echo' \
  -X POST \
  -d '{
  "name": "Eve",
  "action": "create"
}'
```
