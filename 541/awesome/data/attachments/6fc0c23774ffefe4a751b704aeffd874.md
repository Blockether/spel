## PATCH http://localhost:40741/echo → 200 OK

### Request Headers
```
PATCH http://localhost:40741/echo
```

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
date: Thu, 09 Apr 2026 19:57:13 GMT
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
curl 'http://localhost:40741/echo' \
  -X PATCH \
  -d '{
  "email": "eve@new.com"
}'
```
