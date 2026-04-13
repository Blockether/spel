## POST http://localhost:41553/echo → 200 OK

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
date: Mon, 13 Apr 2026 02:46:47 GMT
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
curl 'http://localhost:41553/echo' \
  -X POST \
  -d '{
  "name": "Eve",
  "action": "create"
}'
```
