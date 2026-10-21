## PATCH http://localhost:33261/echo → 200 OK

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
date: Tue, 20 Oct 2026 23:37:42 GMT
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
curl 'http://localhost:33261/echo' \
  -X PATCH \
  -d '{
  "email": "eve@new.com"
}'
```
