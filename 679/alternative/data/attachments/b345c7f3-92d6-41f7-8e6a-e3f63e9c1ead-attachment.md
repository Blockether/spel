## POST http://localhost:35147/echo → 200 OK

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
date: Mon, 03 Aug 2026 17:07:46 GMT
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
curl 'http://localhost:35147/echo' \
  -X POST \
  -d '{
  "name": "Eve",
  "action": "create"
}'
```
