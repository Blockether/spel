## PATCH http://localhost:33953/echo → 200 OK

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
date: Fri, 02 Oct 2026 20:24:08 GMT
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
curl 'http://localhost:33953/echo' \
  -X PATCH \
  -d '{
  "email": "eve@new.com"
}'
```
