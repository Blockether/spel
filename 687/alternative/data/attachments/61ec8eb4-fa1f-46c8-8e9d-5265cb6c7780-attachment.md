## POST http://localhost:39835/echo → 200 OK

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
date: Tue, 04 Aug 2026 22:12:03 GMT
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
curl 'http://localhost:39835/echo' \
  -X POST \
  -d '{
  "name": "Eve",
  "action": "create"
}'
```
