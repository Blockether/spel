## DELETE http://localhost:45309/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Fri, 02 Oct 2026 20:43:39 GMT
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
curl 'http://localhost:45309/echo' \
  -X DELETE
```
