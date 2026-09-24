## POST http://localhost:42431/echo → 200 OK

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
date: Thu, 24 Sep 2026 22:45:27 GMT
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
curl 'http://localhost:42431/echo' \
  -X POST \
  -d '{
  "name": "Eve",
  "action": "create"
}'
```
