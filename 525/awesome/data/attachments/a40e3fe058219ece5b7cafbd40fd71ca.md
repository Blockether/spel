## PATCH http://localhost:34967/echo → 200 OK

### Request Headers
```
PATCH http://localhost:34967/echo
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
date: Thu, 02 Apr 2026 09:59:53 GMT
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
curl 'http://localhost:34967/echo' \
  -X PATCH \
  -d '{
  "email": "eve@new.com"
}'
```
