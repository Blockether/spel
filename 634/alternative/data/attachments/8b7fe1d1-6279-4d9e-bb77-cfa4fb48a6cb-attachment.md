## POST http://localhost:43813/echo → 200 OK

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
date: Fri, 17 Jul 2026 15:42:46 GMT
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
curl 'http://localhost:43813/echo' \
  -X POST \
  -d '{
  "name": "Eve",
  "action": "create"
}'
```
