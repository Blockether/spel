## POST http://localhost:43393/echo → 200 OK

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
date: Wed, 04 Nov 2026 05:36:54 GMT
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
curl 'http://localhost:43393/echo' \
  -X POST \
  -d '{
  "name": "Eve",
  "action": "create"
}'
```
