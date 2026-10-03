## POST http://localhost:39467/echo → 200 OK

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
date: Sat, 03 Oct 2026 13:29:03 GMT
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
curl 'http://localhost:39467/echo' \
  -X POST \
  -d '{
  "name": "Eve",
  "action": "create"
}'
```
