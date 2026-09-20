## POST http://localhost:34753/echo → 200 OK

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
date: Sun, 20 Sep 2026 13:27:36 GMT
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
curl 'http://localhost:34753/echo' \
  -X POST \
  -d '{
  "name": "Eve",
  "action": "create"
}'
```
