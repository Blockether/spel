## POST http://localhost:39567/echo → 200 OK

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
date: Thu, 24 Sep 2026 23:50:51 GMT
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
curl 'http://localhost:39567/echo' \
  -X POST \
  -d '{
  "name": "Eve",
  "action": "create"
}'
```
