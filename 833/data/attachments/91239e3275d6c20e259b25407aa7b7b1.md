## POST http://localhost:46305/echo → 200 OK

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
date: Sat, 21 Nov 2026 15:30:10 GMT
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
curl 'http://localhost:46305/echo' \
  -X POST \
  -d '{
  "name": "Eve",
  "action": "create"
}'
```
