## PATCH http://localhost:33949/echo → 200 OK

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
date: Mon, 26 Oct 2026 14:21:38 GMT
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
curl 'http://localhost:33949/echo' \
  -X PATCH \
  -d '{
  "email": "eve@new.com"
}'
```
