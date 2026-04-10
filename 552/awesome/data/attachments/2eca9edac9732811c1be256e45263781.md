## DELETE http://localhost:38717/echo → 200 OK

### Request Headers
```
DELETE http://localhost:38717/echo
```

### Response Headers
```
content-length: 34
content-type: application/json
date: Fri, 10 Apr 2026 22:29:03 GMT
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
curl 'http://localhost:38717/echo' \
  -X DELETE
```
