## POST http://localhost:37513/echo → 200 OK

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
date: Fri, 11 Sep 2026 14:58:56 GMT
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
curl 'http://localhost:37513/echo' \
  -X POST \
  -d '{
  "name": "Eve",
  "action": "create"
}'
```
