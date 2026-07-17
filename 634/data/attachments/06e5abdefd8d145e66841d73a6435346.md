## PATCH http://localhost:43813/echo → 200 OK

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
date: Fri, 17 Jul 2026 15:42:46 GMT
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
curl 'http://localhost:43813/echo' \
  -X PATCH \
  -d '{
  "email": "eve@new.com"
}'
```
