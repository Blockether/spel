## POST http://localhost:33159/echo → 200 OK

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
date: Wed, 05 Aug 2026 07:16:29 GMT
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
curl 'http://localhost:33159/echo' \
  -X POST \
  -d '{
  "name": "Eve",
  "action": "create"
}'
```
