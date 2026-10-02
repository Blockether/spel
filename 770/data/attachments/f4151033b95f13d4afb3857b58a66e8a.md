## PUT http://localhost:33953/echo → 200 OK

### Request Body
```json
{
  "name": "Eve Updated"
}
```

### Response Headers
```
content-length: 61
content-type: application/json
date: Fri, 02 Oct 2026 20:24:08 GMT
```

### Response Body
```json
{
  "method": "PUT",
  "path": "/echo",
  "body": {
    "name": "Eve Updated"
  }
}
```

### cURL
```bash
curl 'http://localhost:33953/echo' \
  -X PUT \
  -d '{
  "name": "Eve Updated"
}'
```
