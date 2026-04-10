## PUT http://localhost:42513/echo → 200 OK

### Request Headers
```
PUT http://localhost:42513/echo
```

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
date: Fri, 10 Apr 2026 21:39:02 GMT
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
curl 'http://localhost:42513/echo' \
  -X PUT \
  -d '{
  "name": "Eve Updated"
}'
```
