## PATCH http://localhost:38523/echo → 200 OK

### Request Body
```json
{
  "email": "eve@new.com"
}
```

### Response Headers
```
content-length: 64
content-type: application/json
date: Sun, 22 Nov 2026 13:33:17 GMT
```

### Response Body
```json
{
  "method": "PATCH",
  "path": "/echo",
  "body": {
    "email": "eve@new.com"
  }
}
```

### cURL
```bash
curl 'http://localhost:38523/echo' \
  -X PATCH \
  -d '{
  "email": "eve@new.com"
}'
```
