## PATCH http://localhost:41313/echo → 200 OK

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
date: Sat, 03 Oct 2026 07:03:23 GMT
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
curl 'http://localhost:41313/echo' \
  -X PATCH \
  -d '{
  "email": "eve@new.com"
}'
```
