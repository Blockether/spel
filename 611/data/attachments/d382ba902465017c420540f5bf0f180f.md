## DELETE http://localhost:33229/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Fri, 17 Apr 2026 13:13:17 GMT
```

### Response Body
```json
{
  "method": "DELETE",
  "path": "/echo"
}
```

### cURL
```bash
curl 'http://localhost:33229/echo' \
  -X DELETE
```
