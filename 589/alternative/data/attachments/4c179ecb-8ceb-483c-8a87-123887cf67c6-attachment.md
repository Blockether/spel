## PATCH http://localhost:41665/echo → 200 OK

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
date: Sun, 12 Apr 2026 15:50:59 GMT
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
curl 'http://localhost:41665/echo' \
  -X PATCH \
  -d '{
  "email": "eve@new.com"
}'
```
