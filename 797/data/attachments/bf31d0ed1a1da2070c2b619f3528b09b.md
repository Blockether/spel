## POST http://localhost:35509/echo → 200 OK

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
date: Sat, 24 Oct 2026 08:08:32 GMT
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
curl 'http://localhost:35509/echo' \
  -X POST \
  -d '{
  "name": "Eve",
  "action": "create"
}'
```
