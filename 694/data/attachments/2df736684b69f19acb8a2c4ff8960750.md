## POST http://localhost:44371/echo → 200 OK

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
date: Mon, 10 Aug 2026 08:48:57 GMT
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
curl 'http://localhost:44371/echo' \
  -X POST \
  -d '{
  "name": "Eve",
  "action": "create"
}'
```
