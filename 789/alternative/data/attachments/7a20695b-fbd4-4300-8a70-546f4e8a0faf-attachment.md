## PATCH http://localhost:38313/echo → 200 OK

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
date: Wed, 14 Oct 2026 13:23:26 GMT
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
curl 'http://localhost:38313/echo' \
  -X PATCH \
  -d '{
  "email": "eve@new.com"
}'
```
