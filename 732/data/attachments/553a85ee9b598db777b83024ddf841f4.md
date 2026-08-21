## POST http://localhost:42325/echo → 200 OK

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
date: Fri, 21 Aug 2026 03:01:52 GMT
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
curl 'http://localhost:42325/echo' \
  -X POST \
  -d '{
  "name": "Eve",
  "action": "create"
}'
```
