## POST http://localhost:39513/echo → 200 OK

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
date: Mon, 12 Oct 2026 16:15:43 GMT
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
curl 'http://localhost:39513/echo' \
  -X POST \
  -d '{
  "name": "Eve",
  "action": "create"
}'
```
