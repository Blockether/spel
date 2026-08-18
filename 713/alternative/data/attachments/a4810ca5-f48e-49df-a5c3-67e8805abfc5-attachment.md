## POST http://localhost:38475/echo → 200 OK

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
date: Tue, 18 Aug 2026 00:20:20 GMT
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
curl 'http://localhost:38475/echo' \
  -X POST \
  -d '{
  "name": "Eve",
  "action": "create"
}'
```
